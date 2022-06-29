package com.yapp.betree.service;

import com.yapp.betree.domain.Folder;
import com.yapp.betree.domain.FruitType;
import com.yapp.betree.domain.Message;
import com.yapp.betree.domain.User;
import com.yapp.betree.dto.request.MessageRequestDto;
import com.yapp.betree.dto.response.MessageBoxResponseDto;
import com.yapp.betree.dto.response.MessagePageResponseDto;
import com.yapp.betree.exception.BetreeException;
import com.yapp.betree.exception.ErrorCode;
import com.yapp.betree.repository.FolderRepository;
import com.yapp.betree.repository.MessageRepository;
import com.yapp.betree.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.yapp.betree.exception.ErrorCode.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MessageService {

    private final UserRepository userRepository;
    private final FolderRepository folderRepository;
    private final MessageRepository messageRepository;

    /**
     * 칭찬 메세지 생성 (물 주기)
     *
     * @param senderId   발신유저아이디
     * @param requestDto messageRequestDto
     * @return
     */
    @Transactional
    public Long createMessage(Long senderId, MessageRequestDto requestDto) {

        //수신자 유저 객체 조회
        User user = userRepository.findById(requestDto.getReceiverId()).orElseThrow(() -> new BetreeException(USER_NOT_FOUND, "receiverId = " + requestDto.getReceiverId()));

        Folder folder;
        if (requestDto.getFolderId() == null) {
            //상대방 디폴트 폴더로 지정
            folder = folderRepository.findByUserIdAndFruit(requestDto.getReceiverId(), FruitType.DEFAULT);
        } else {
            folder = folderRepository.findById(requestDto.getFolderId()).orElseThrow(() -> new BetreeException(TREE_NOT_FOUND, "tree = " + requestDto.getFolderId()));
        }

        Message message = Message.builder()
                .senderId(senderId)
                .user(user)
                .anonymous(requestDto.isAnonymous())
                .content(requestDto.getContent())
                .folder(folder)
                .build();


        //로그인 안 한 상태에서 메세지 전송시 익명 여부 true 설정
        if (senderId == -1L && !message.isAnonymous()) {
            message.updateAnonymous();
        }

        // 본인에게 보낸 메세지일 때 읽음 여부 true 설정
        if (Objects.equals(senderId, requestDto.getReceiverId())) {
            message.updateAlreadyRead();
        }

        return messageRepository.save(message).getId();
    }

    /**
     * 메세지 목록 조회
     * - treeId 입력시 폴더별 조회
     *
     * @param userId
     * @param pageable
     * @param treeId
     * @return
     */
    public MessagePageResponseDto getMessageList(Long userId, Pageable pageable, Long treeId) {

        //다음 페이지 존재 여부
        boolean hasNext = messageRepository.findByUserId(userId, pageable).hasNext();

        Slice<Message> messages;
        if (treeId == null) {
            //기본 폴더 목록 조회
            Long defaultTreeId = folderRepository.findByUserIdAndFruit(userId, FruitType.DEFAULT).getId();
            messages = messageRepository.findByUserIdAndFolderId(userId, defaultTreeId, pageable);
        } else {
            //해당 폴더 메세지 목록 조회
            messages = messageRepository.findByUserIdAndFolderId(userId, treeId, pageable);
        }

        List<MessageBoxResponseDto> responseDtos = new ArrayList<>();
        //익명인 메세지 저장 구분
        for (Message message : messages) {
            if (message.isAnonymous()) {
                responseDtos.add(new MessageBoxResponseDto(message, "익명", "기본이미지"));
            } else {
                User sender = userRepository.findById(message.getSenderId()).orElseThrow(() -> new BetreeException(ErrorCode.USER_NOT_FOUND, "senderId = " + message.getSenderId()));
                responseDtos.add(MessageBoxResponseDto.of(message, sender));
            }
        }
        return new MessagePageResponseDto(responseDtos, hasNext);
    }

    /**
     * 선택한 메세지 공개로 설정 (열매 맺기)
     *
     * @param userId
     * @param messageIds
     */
    @Transactional
    public void updateMessageOpening(Long userId, List<Long> messageIds) {
        //선택한 개수 8개 초과면 오류
        if (messageIds.size() > 8) {
            throw new BetreeException(ErrorCode.INVALID_INPUT_VALUE, "열매로 맺을 수 있는 메세지 개수는 최대 8개입니다.");
        }
        //이미 선택된 메세지 가져와서 false로 변경
        messageRepository.findByUserIdAndOpening(userId, true).forEach(Message::updateOpening);

        // 지금 선택된 메세지만 true로 변경
        for (Long id : messageIds) {
            messageRepository.findById(id).orElseThrow(() -> new BetreeException(MESSAGE_NOT_FOUND, "messageId = " + id)).updateOpening();
        }
    }

    /**
     * 메세지 삭제
     *
     * @param userId
     * @param messageIds
     */
    @Transactional
    public void deleteMessages(Long userId, List<Long> messageIds) {

        messageIds.forEach(messageId -> {
            Message message = messageRepository.findByIdAndUserId(messageId, userId).orElseThrow(() -> new BetreeException(MESSAGE_NOT_FOUND, "messageId = " + messageId + "userId = " + userId));
            messageRepository.delete(message);
        });
    }

    /**
     * 메세지 이동
     *
     * @param userId
     * @param messageIds
     * @param treeId
     */
    @Transactional
    public void moveMessageFolder(Long userId, List<Long> messageIds, Long treeId) {

        Folder folder = folderRepository.findById(treeId).orElseThrow(() -> new BetreeException(TREE_NOT_FOUND, "treeId = " + treeId));

        messageIds.forEach(messageId -> messageRepository.findByIdAndUserId(messageId, userId).orElseThrow(() -> new BetreeException(MESSAGE_NOT_FOUND, "messageId =" + messageId + "userId = " + userId))
                .updateFolder(folder));
    }

    /**
     * 메세지 즐겨찾기 상태 변경
     *
     * @param userId
     * @param messageId
     */
    @Transactional
    public void updateFavoriteMessage(Long userId, Long messageId) {

        messageRepository.findByIdAndUserId(messageId, userId).orElseThrow(() -> new BetreeException(MESSAGE_NOT_FOUND, "messageId =" + messageId))
                .updateFavorite();
    }

    /**
     * 즐겨찾기한 메세지 목록 조회
     *
     * @param userId
     * @param pageable
     * @return
     */
    @Transactional
    public MessagePageResponseDto getFavoriteMessage(Long userId, Pageable pageable) {

        //다음 페이지 존재 여부
        Slice<Message> messages = messageRepository.findByUserIdAndFavorite(userId, true, pageable);

        List<MessageBoxResponseDto> responseMessages = messages
                .stream()
                .map(message -> {
                    User sender = userRepository.findById(message.getSenderId()).orElseThrow(() -> new BetreeException(USER_NOT_FOUND));
                    return new MessageBoxResponseDto(message, sender.getNickname(), sender.getUserImage());
                })
                .collect(Collectors.toList());

        return new MessagePageResponseDto(responseMessages, messages.hasNext());
    }

    /**
     * 메세지 읽음 여부 상태 변경
     *
     * @param userId
     * @param messageId
     */
    @Transactional
    public void updateReadMessage(Long userId, Long messageId) {

        messageRepository.findByIdAndUserId(messageId, userId).orElseThrow(() -> new BetreeException(MESSAGE_NOT_FOUND, "messageId =" + messageId))
                .updateAlreadyRead();
    }
}
